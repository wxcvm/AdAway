
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
#include <windows.h>
#include <shellapi.h>
#include <wincrypt.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdint.h>
#include <wchar.h>

#include <openssl/x509.h>
#include <openssl/pem.h>

#include "gui_win32.h"

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
};

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
    sn->valid = 1;
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
    fclose(f);
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

static long long cert_days_left(const char *cert_path) {
    X509 *x = load_pem_cert(cert_path);
    if (!x) return -100000;
    const ASN1_TIME *na = X509_get0_notAfter(x);
    if (!na) { X509_free(x); return -100000; }
    int y, mo, d, h, mi, s;
    if (sscanf((const char *)na->data, "%4d%2d%2d%2d%2d%2d", &y, &mo, &d, &h, &mi, &s) != 6) {
        X509_free(x);
        return -100000;
    }
    struct tm tm = {0};
    tm.tm_year = y - 1900; tm.tm_mon = mo - 1; tm.tm_mday = d;
    tm.tm_hour = h; tm.tm_min = mi; tm.tm_sec = s;
    long long expiry = (long long)mktime(&tm);
    X509_free(x);
    return (expiry - (long long)time(NULL)) / 86400LL;
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
#define IDC_HTTP_EDIT 1101
#define IDC_HTTPS_EDIT 1102
#define IDC_BIND_CHK  1103
#define IDC_SAVE      1104
#define IDC_RESTART   1105
#define IDC_FLUSH     1106

/* AdGuard-inspired palette */
#define C_SIDEBAR    RGB(30, 37, 55)
#define C_NAV_ACTIVE RGB(42, 54, 84)
#define C_NAV_TEXT   RGB(147, 163, 190)
#define C_CONTENT_BG RGB(243, 245, 248)
#define C_CARD       RGB(255, 255, 255)
#define C_BORDER     RGB(229, 232, 238)
#define C_TEXT       RGB(31, 41, 55)
#define C_MUTED      RGB(100, 116, 139)
#define C_ACCENT     RGB(59, 130, 246)
#define C_BLUE_SOFT  RGB(219, 234, 254)
#define C_BLUE       RGB(59, 130, 246)
#define C_RED        RGB(239, 68, 68)
#define C_GREEN      RGB(34, 197, 94)
#define C_GREEN_TXT  RGB(22, 163, 74)
#define C_GRIDLINE   RGB(237, 240, 244)

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
    rounded_card(hdc, x, y, w, h, 12, C_CARD, C_BORDER);
    HBRUSH ab = CreateSolidBrush(accent);
    HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
    HGDIOBJ ob = SelectObject(hdc, ab);
    Ellipse(hdc, S(x + 14), S(y + 14), S(x + 26), S(y + 26));
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(ab);
    HFONT vf = mfont(21, FW_SEMIBOLD);
    HFONT lf = mfont(12, FW_NORMAL);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, C_TEXT);
    HFONT old = (HFONT)SelectObject(hdc, vf);
    TextOutW(hdc, S(x + 16), S(y + 10), value, (int)wcslen(value));
    SetTextColor(hdc, C_MUTED);
    SelectObject(hdc, lf);
    TextOutW(hdc, S(x + 16), S(y + 40), label, (int)wcslen(label));
    SelectObject(hdc, old);
    DeleteObject(vf);
    DeleteObject(lf);
}

static void draw_chart(HDC hdc, RECT panel, const wchar_t *title,
                       const struct histogram *h, int count) {
    rounded_card(hdc, panel.left, panel.top,
                 panel.right - panel.left, panel.bottom - panel.top, 12, C_CARD, C_BORDER);
    HFONT tf = mfont(14, FW_SEMIBOLD);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, C_TEXT);
    HFONT old = (HFONT)SelectObject(hdc, tf);
    TextOutW(hdc, S(panel.left + 16), S(panel.top + 12), title, (int)wcslen(title));
    SelectObject(hdc, old);
    DeleteObject(tf);

    int ax = panel.left + 20, ay = panel.top + 42;
    int aw = panel.right - panel.left - 34, ah = panel.bottom - panel.top - 62;
    if (count <= 0 || aw < 10 || ah < 10) {
        SetTextColor(hdc, C_MUTED);
        TextOutW(hdc, S(ax + 4), S(ay + 12), L"暂无数据", 4);
        return;
    }
    long long maxv = 1;
    for (int i = 0; i < count; i++) {
        if (h[i].requests > maxv) maxv = h[i].requests;
        if (h[i].blocked > maxv) maxv = h[i].blocked;
    }
    HPEN gp = CreatePen(PS_SOLID, 1, C_GRIDLINE);
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
    SetTextColor(hdc, C_MUTED);
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
    SetTextColor(hdc, C_MUTED);
    TextOutW(hdc, S(x + 14), S(y), L"请求", 2);
    HBRUSH r = CreateSolidBrush(C_RED);
    ob = SelectObject(hdc, r);
    Ellipse(hdc, S(x + 76), S(y + 3), S(x + 84), S(y + 11));
    SelectObject(hdc, ob);
    DeleteObject(r);
    TextOutW(hdc, S(x + 90), S(y), L"拦截", 2);
}

static void draw_statusbar(HDC hdc) {
    RECT strip = {0, 630, 1000, 678};
    FillRect(hdc, &strip, GetSysColorBrush(COLOR_WINDOW));
    HPEN tp = CreatePen(PS_SOLID, 1, C_BORDER);
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
    long long days = cert_days_left(cert_path);
    wchar_t cert[160];
    if (days > 0 && days < 100000)
        swprintf(cert, 160, L"CA 证书剩余 %lld 天 · %s", days,
                 cert_trusted(cert_path) ? L"已信任" : L"未信任");
    else
        swprintf(cert, 160, L"CA 证书不可用");
    SetTextColor(hdc, C_MUTED);
    TextOutW(hdc, S(430), S(643), cert, (int)wcslen(cert));

    SYSTEMTIME st;
    GetLocalTime(&st);
    wchar_t tm[64];
    swprintf(tm, 64, L"%02d:%02d:%02d", st.wHour, st.wMinute, st.wSecond);
    SetTextColor(hdc, RGB(148, 163, 184));
    TextOutW(hdc, S(920), S(643), tm, (int)wcslen(tm));

    SetTextColor(hdc, C_MUTED);
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
    HBRUSH bg = CreateSolidBrush(C_SIDEBAR);
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
    SelectObject(hdc, old);
    DeleteObject(lf);
    DeleteObject(sf);

    /* nav items */
    for (int t = 0; t < 2; t++) {
        int ny = t == 0 ? 96 : 148;
        bool active = (g_tab == t);
        rounded_card(hdc, 16, ny, 160, 40, 10,
                     active ? C_NAV_ACTIVE : C_SIDEBAR,
                     active ? C_NAV_ACTIVE : C_SIDEBAR);
        if (active) {
            HBRUSH ab = CreateSolidBrush(C_ACCENT);
            RECT bar = {S(16), S(ny + 10), S(20), S(ny + 30)};
            FillRect(hdc, &bar, ab);
            DeleteObject(ab);
        }
        /* icon: bars (statistics) / sliders (settings) */
        if (t == 0) {
            HBRUSH ib = CreateSolidBrush(active ? C_ACCENT : C_NAV_TEXT);
            HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
            HGDIOBJ ob = SelectObject(hdc, ib);
            RoundRect(hdc, S(34), S(ny + 14), S(40), S(ny + 30), S(3), S(3));
            RoundRect(hdc, S(43), S(ny + 8),  S(49), S(ny + 30), S(3), S(3));
            RoundRect(hdc, S(52), S(ny + 20), S(58), S(ny + 30), S(3), S(3));
            SelectObject(hdc, op);
            SelectObject(hdc, ob);
            DeleteObject(ib);
        } else {
            HPEN ip = CreatePen(PS_SOLID, S(2), active ? C_ACCENT : C_NAV_TEXT);
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
        SetTextColor(hdc, active ? RGB(255, 255, 255) : C_NAV_TEXT);
        TextOutW(hdc, S(70), S(ny + 10), t == 0 ? L"统计" : L"设置", 2);
        SelectObject(hdc, old);
        DeleteObject(nf);
    }

    /* exit at sidebar bottom */
    rounded_card(hdc, 16, 576, 160, 40, 10, C_SIDEBAR, C_SIDEBAR);
    HFONT xf = mfont(13, FW_NORMAL);
    SelectObject(hdc, xf);
    SetTextColor(hdc, C_NAV_TEXT);
    TextOutW(hdc, S(70), S(586), L"退出", 2);
    SelectObject(hdc, old);
    DeleteObject(xf);
}

/* ── system tray icon ──────────────────────────────────────────── */
#define WM_APP_TRAY (WM_USER + 1)
#define WM_APP_EXIT (WM_USER + 2)

static NOTIFYICONDATAW g_nid;
static bool g_tray_initialized = false;

static void tray_add(HWND hwnd) {
    memset(&g_nid, 0, sizeof(g_nid));
    g_nid.cbSize = sizeof(g_nid);
    g_nid.hWnd = hwnd;
    g_nid.uID = 1;
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    g_nid.hIcon = LoadIconW(NULL, IDI_APPLICATION);
    swprintf(g_nid.szTip, 128, L"ADBlock 拦截服务器 - http://localhost:%d", g_http_port);
    Shell_NotifyIconW(NIM_ADD, &g_nid);
    g_tray_initialized = true;
    g_nid.uFlags |= NIF_INFO;
    wcscpy(g_nid.szInfoTitle, L"ADBlock 拦截服务器");
    wcscpy(g_nid.szInfo, L"运行中 - 双击此图标可重新打开窗口。");
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
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
        snprintf(ccmd, sizeof(ccmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d --no-gui",
                 exe, g_res, g_http_port, g_https_port);
        win32_autostart_set(on, ccmd);
        SendMessageW(chk, BM_SETCHECK, on ? BST_CHECKED : BST_UNCHECKED, 0);
        swprintf(g_status, 4096, L"开机自启动已%s", on ? L"启用" : L"关闭");
        InvalidateRect(hwnd, NULL, FALSE);
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
    { IDM_TRUST,      210, 404, 96, 28, L"BUTTON", L"信任 CA", BS_PUSHBUTTON },
    { IDM_UNTRUST,    316, 404, 96, 28, L"BUTTON", L"撤销 CA", BS_PUSHBUTTON },
    { IDM_TEST,       422, 404, 110, 28, L"BUTTON", L"打开测试页", BS_PUSHBUTTON },
    { IDC_FLUSH,      542, 404, 96, 28, L"BUTTON", L"清空统计", BS_PUSHBUTTON },
    { IDM_AUTOSTART,  648, 406, 180, 24, L"BUTTON", L"开机自启动", BS_AUTOCHECKBOX },
};
#define CL_MAIN 10

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
    control_post(g_http_port, "reload_config", st, 128);
    swprintf(g_status, 4096, L"拦截策略已更新（%ls）", st);
    InvalidateRect(hwnd, NULL, FALSE);
}

static LRESULT CALLBACK gui_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
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
        g_tab = 0;
        g_scale = GetDpiForWindow(hwnd) / 96.0;
        g_sn = (struct snapshot *)calloc(1, sizeof(struct snapshot));
        if (args->startup_warning != NULL && args->startup_warning[0] != '\0')
            swprintf(g_status, 4096, L"%hs", args->startup_warning);
        HINSTANCE hinst = GetModuleHandleW(NULL);
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
        tray_add(hwnd);
        SetTimer(hwnd, 1, 2000, NULL);
        swprintf(g_status, 4096, L"等待首次统计（http://127.0.0.1:%d/internal-stats）...", g_http_port);
        return 0;
    }
    case WM_TIMER:
        if (g_sn) {
            snapshot_fetch(g_http_port, g_sn);
            if (g_sn->valid) {
                SYSTEMTIME st;
                GetLocalTime(&st);
                swprintf(g_status, 4096,
                         L"实时数据 - 更新于 %02d:%02d:%02d", st.wHour, st.wMinute, st.wSecond);
            } else {
                swprintf(g_status, 4096, L"服务器暂不可达，等待响应...");
            }
        }
        InvalidateRect(hwnd, NULL, FALSE);
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
                MessageBoxW(hwnd, msg, L"证书", MB_OK | MB_ICONINFORMATION);
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
                snprintf(cmd, sizeof(cmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d --no-gui",
                         exe, g_res, g_http_port, g_https_port);
                win32_autostart_set(on, cmd);
                swprintf(g_status, 4096, L"开机自启动已%s", on ? L"启用" : L"关闭");
                InvalidateRect(hwnd, NULL, FALSE);
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
                        wchar_t exeW[MAX_PATH], resW[1024], argsW[2048];
                        GetModuleFileNameW(NULL, exeW, MAX_PATH);
                        utf8_to_wide(g_res, resW, 1024);
                        swprintf(argsW, 2048, L"--resources \"%ls\"", resW);
                        ShellExecuteW(NULL, L"open", exeW, argsW, NULL, SW_SHOWNORMAL);
                        PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
                    } else {
                        swprintf(g_status, 4096,
                                 L"设置已保存 - 点击\"保存并重启\"应用新端口绑定");
                        InvalidateRect(hwnd, NULL, FALSE);
                    }
                }
            } else if (id == IDC_FLUSH) {
                wchar_t st[128];
                control_post(g_http_port, "flush_stats", st, 128);
                swprintf(g_status, 4096, L"%ls", st);
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id >= IDC_POL0 && id < IDC_POL0 + POLICY_COUNT) {
                policy_apply(hwnd);
            }
        }
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        RECT rc;
        GetClientRect(hwnd, &rc);
        FillRect(hdc, &rc, GetSysColorBrush(COLOR_WINDOW));

        /* content background */
        RECT content = {0, 0, rc.right, rc.bottom};
        HBRUSH cb = CreateSolidBrush(C_CONTENT_BG);
        FillRect(hdc, &content, cb);
        DeleteObject(cb);

        draw_sidebar(hdc);

        if (g_tab == 1) {
            HFONT shf = mfont(15, FW_SEMIBOLD);
            HFONT old = (HFONT)SelectObject(hdc, shf);
            SetBkMode(hdc, TRANSPARENT);
            SetTextColor(hdc, C_TEXT);
            TextOutW(hdc, S(210), S(20), L"服务器", 3);
            TextOutW(hdc, S(210), S(176), L"拦截策略（保存后立即生效）", 12);
            TextOutW(hdc, S(210), S(372), L"证书与维护", 5);
            SelectObject(hdc, old);
            DeleteObject(shf);
            draw_statusbar(hdc);
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
        long long days = cert_days_left(cert_path);
        int ok = days > 0 && days < 100000;
        int trusted = cert_trusted(cert_path);
        HFONT lf = mfont(13, FW_NORMAL);
        HFONT old2 = (HFONT)SelectObject(hdc, lf);
        if (ok)
            swprintf(txt, 256, L"证书：剩余 %lld 天 · %s", days,
                     trusted ? L"已在 Windows 受信任根中，浏览器绿色锁" : L"未信任（点击左侧\"设置\"→ 信任 CA）");
        else
            swprintf(txt, 256, L"证书：不可用（请检查 resources 目录）");
        SetTextColor(hdc, ok ? C_GREEN_TXT : RGB(190, 70, 70));
        TextOutW(hdc, S(210), S(444), txt, (int)wcslen(txt));
        SelectObject(hdc, old2);
        DeleteObject(lf);

        draw_statusbar(hdc);
        EndPaint(hwnd, &ps);
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
            (WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX),
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
