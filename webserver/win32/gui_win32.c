/*
 * gui_win32.c - native Windows 11 dashboard for the ADBlock web server.
 *
 * The server itself runs as a daemon with a local REST API
 * (/internal-stats). This module hosts a small Win32 dashboard window in
 * the main process: it polls the stats API, draws live KPIs and hourly /
 * daily bar charts, shows the localhost certificate status (and can
 * install/remove it from the user's Trusted Root store) and exposes a
 * "Start with Windows" toggle (HKCU Run key). No web page is required.
 *
 * Windows-only: this file is not part of the Android build.
 */
#define _WIN32_WINNT 0x0601
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

/* ── small helpers ─────────────────────────────────────────────── */

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

static int cert_trusted(const char *cert_path) {
    size_t derlen = 0;
    unsigned char *der = pem_to_der(cert_path, &derlen);
    if (!der) return 0;
    CERT_CONTEXT *ours = CertCreateCertificateContext(X509_ASN_ENCODING, der, (DWORD)derlen);
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
                    if (CertCompareCertificate(X509_ASN_ENCODING, ours, ctx)) {
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
    CERT_CONTEXT *ours = CertCreateCertificateContext(X509_ASN_ENCODING, der, (DWORD)derlen);
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
                    if (CertCompareCertificate(X509_ASN_ENCODING, ours, ctx)) {
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

static const wchar_t *g_title = L"ADBlock Web Server - Dashboard";

static void fmt_num(wchar_t *dst, size_t n, long long v) {
    if (v >= 1000000) swprintf(dst, n, L"%.1fM", (double)v / 1000000.0);
    else if (v >= 1000) swprintf(dst, n, L"%.1fK", (double)v / 1000.0);
    else swprintf(dst, n, L"%lld", v);
}

static void draw_kpi(HDC hdc, int x, int y, int w, int h,
                     const wchar_t *label, const wchar_t *value) {
    RECT r = {x, y, x + w, y + h};
    HBRUSH bg = CreateSolidBrush(RGB(245, 247, 250));
    FillRect(hdc, &r, bg);
    DeleteObject(bg);
    FrameRect(hdc, &r, GetSysColorBrush(COLOR_BTNSHADOW));
    HFONT vf = CreateFontW(26, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
    HFONT lf = CreateFontW(15, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, RGB(33, 150, 243));
    HFONT old = (HFONT)SelectObject(hdc, vf);
    TextOutW(hdc, x + 12, y + 8, value, (int)wcslen(value));
    SetTextColor(hdc, RGB(96, 110, 130));
    SelectObject(hdc, lf);
    TextOutW(hdc, x + 12, y + 40, label, (int)wcslen(label));
    SelectObject(hdc, old);
    DeleteObject(vf);
    DeleteObject(lf);
}

static void draw_chart(HDC hdc, RECT panel, const wchar_t *title,
                       const struct histogram *h, int count) {
    FillRect(hdc, &panel, GetSysColorBrush(COLOR_WINDOW));
    FrameRect(hdc, &panel, GetSysColorBrush(COLOR_BTNSHADOW));
    HFONT tf = CreateFontW(17, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, RGB(60, 70, 90));
    HFONT old = (HFONT)SelectObject(hdc, tf);
    TextOutW(hdc, panel.left + 10, panel.top + 6, title, (int)wcslen(title));
    SelectObject(hdc, old);
    DeleteObject(tf);

    RECT area = {panel.left + 14, panel.top + 34, panel.right - 8, panel.bottom - 22};
    if (count <= 0 || area.right - area.left < 10 || area.bottom - area.top < 10) {
        SetTextColor(hdc, RGB(150, 150, 150));
        TextOutW(hdc, area.left + 6, area.top + 10, L"no data yet", 11);
        return;
    }
    long long maxv = 1;
    for (int i = 0; i < count; i++) {
        if (h[i].requests > maxv) maxv = h[i].requests;
        if (h[i].blocked > maxv) maxv = h[i].blocked;
    }
    wchar_t maxbuf[64];
    swprintf(maxbuf, 64, L"%lld", maxv);
    SetTextColor(hdc, RGB(150, 150, 150));
    TextOutW(hdc, area.left - 2, area.top - 2, maxbuf, (int)wcslen(maxbuf));
    SetTextColor(hdc, RGB(150, 150, 150));
    TextOutW(hdc, area.left - 2, area.bottom - 4, L"0", 1);

    int slotw = (area.right - area.left) / count;
    if (slotw < 2) slotw = 2;
    HBRUSH blue = CreateSolidBrush(RGB(33, 150, 243));
    HBRUSH red = CreateSolidBrush(RGB(244, 67, 54));
    for (int i = 0; i < count; i++) {
        int chart_h = area.bottom - area.top;
        int bh1 = (int)((double)chart_h * (double)h[i].requests / (double)maxv);
        int bh2 = (int)((double)chart_h * (double)h[i].blocked / (double)maxv);
        RECT rb = {area.left + i * slotw, area.bottom - bh1,
                   area.left + i * slotw + (slotw * 2) / 5, area.bottom};
        if (bh1 > 0) FillRect(hdc, &rb, blue);
        RECT rr = {area.left + i * slotw + (slotw * 3) / 5, area.bottom - bh2,
                   area.left + i * slotw + slotw, area.bottom};
        if (bh2 > 0) FillRect(hdc, &rr, red);
    }
    DeleteObject(blue);
    DeleteObject(red);
}

static void draw_legend(HDC hdc, int x, int y) {
    HBRUSH b = CreateSolidBrush(RGB(33, 150, 243));
    RECT rb = {x, y + 2, x + 12, y + 10};
    FillRect(hdc, &rb, b);
    DeleteObject(b);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, RGB(80, 90, 105));
    TextOutW(hdc, x + 18, y, L"requests", 8);
    HBRUSH r = CreateSolidBrush(RGB(244, 67, 54));
    RECT rr = {x + 110, y + 2, x + 122, y + 10};
    FillRect(hdc, &rr, r);
    DeleteObject(r);
    TextOutW(hdc, x + 128, y, L"blocked", 7);
}

static LRESULT CALLBACK gui_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    static struct snapshot *g_sn = NULL;
    static char g_res[512] = {0};
    static int g_http_port = 8080;
    static int g_https_port = 8443;
    static wchar_t g_status[4096] = L"";

    switch (msg) {
    case WM_CREATE: {
        const struct adblock_gui_args *args =
            (const struct adblock_gui_args *)((CREATESTRUCTW *)lp)->lpCreateParams;
        snprintf(g_res, sizeof(g_res), "%s", args->resource_dir);
        g_http_port = args->http_port;
        g_https_port = args->https_port;
        g_sn = (struct snapshot *)calloc(1, sizeof(struct snapshot));
        HINSTANCE hinst = GetModuleHandleW(NULL);
        int x = 20, y = 442, w = 118, h = 30;
        CreateWindowExW(0, L"BUTTON", L"Trust CA",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, x, y, w, h, hwnd, (HMENU)IDM_TRUST, hinst, 0);
        x += w + 12;
        CreateWindowExW(0, L"BUTTON", L"Remove CA",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, x, y, 128, h, hwnd, (HMENU)IDM_UNTRUST, hinst, 0);
        x += 128 + 12;
        CreateWindowExW(0, L"BUTTON", L"Open Test Page",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, x, y, 150, h, hwnd, (HMENU)IDM_TEST, hinst, 0);
        x += 150 + 12;
        HWND chk = CreateWindowExW(0, L"BUTTON", L"Start with Windows",
            WS_CHILD | WS_VISIBLE | BS_AUTOCHECKBOX, x, y + 2, 200, 26, hwnd,
            (HMENU)IDM_AUTOSTART, hinst, 0);
        SendMessageW(chk, BM_SETCHECK, win32_autostart_installed() ? BST_CHECKED : BST_UNCHECKED, 0);
        x += 200 + 90;
        CreateWindowExW(0, L"BUTTON", L"Exit",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 862, y, 118, h, hwnd, (HMENU)IDM_EXIT, hinst, 0);
        SetTimer(hwnd, 1, 2000, NULL);
        swprintf(g_status, 4096, L"polling http://127.0.0.1:%d/internal-stats ...", g_http_port);
        return 0;
    }
    case WM_TIMER:
        if (g_sn) snapshot_fetch(g_http_port, g_sn);
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_COMMAND:
        if (HIWORD(wp) == BN_CLICKED) {
            int id = LOWORD(wp);
            if (id == IDM_TRUST || id == IDM_UNTRUST) {
                char cert_path[1024];
                snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
                int rc = cert_trust_set(cert_path, id == IDM_TRUST);
                wchar_t msg[256];
                if (rc == 0)
                    swprintf(msg, 256, L"CA certificate %s. Browsers will now trust HTTPS://localhost.",
                             id == IDM_TRUST ? L"added to Trusted Root" : L"removed from Trusted Root");
                else
                    swprintf(msg, 256, L"Certificate operation failed (is the resources dir present?).");
                MessageBoxW(hwnd, msg, L"Certificate", MB_OK | MB_ICONINFORMATION);
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id == IDM_TEST) {
                wchar_t url[256];
                swprintf(url, 256, L"https://localhost:%d/internal-test", g_https_port);
                ShellExecuteW(NULL, L"open", url, NULL, NULL, SW_SHOWNORMAL);
            } else if (id == IDM_AUTOSTART) {
                HWND chk = GetDlgItem(hwnd, IDM_AUTOSTART);
                bool on = SendMessageW(chk, BM_GETCHECK, 0, 0) == BST_CHECKED;
                char cmd[2048];
                char exe[MAX_PATH];
                GetModuleFileNameA(NULL, exe, sizeof(exe));
                snprintf(cmd, sizeof(cmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d --no-gui",
                         exe, g_res, g_http_port, g_https_port);
                win32_autostart_set(on, cmd);
            } else if (id == IDM_EXIT) {
                PostMessageW(hwnd, WM_CLOSE, 0, 0);
            }
        }
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        RECT rc;
        GetClientRect(hwnd, &rc);
        FillRect(hdc, &rc, GetSysColorBrush(COLOR_WINDOW));

        HFONT tf = CreateFontW(26, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
            DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
            DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, RGB(25, 40, 70));
        HFONT old = (HFONT)SelectObject(hdc, tf);
        TextOutW(hdc, 20, 14, g_title, (int)wcslen(g_title));
        SelectObject(hdc, old);
        DeleteObject(tf);

        const struct snapshot *sn = g_sn;
        wchar_t val[64], txt[256];
        long long req = sn && sn->valid ? sn->total_requests : 0;
        long long blk = 0;
        if (sn && sn->valid) {
            for (int i = 0; i < sn->hist_count; i++) blk += sn->hist[i].blocked;
        }
        fmt_num(val, 64, req);
        draw_kpi(hdc, 20, 78, 155, 64, L"Requests", val);
        fmt_num(val, 64, blk);
        draw_kpi(hdc, 187, 78, 155, 64, L"Blocked", val);
        long long rate = (sn && sn->valid) ? (long long)(sn->block_rate * 100.0) : 0;
        swprintf(val, 64, L"%lld%%", rate);
        draw_kpi(hdc, 354, 78, 155, 64, L"Block rate", val);
        fmt_num(val, 64, sn && sn->valid ? sn->total_connections : 0);
        draw_kpi(hdc, 521, 78, 155, 64, L"Connections", val);
        fmt_num(val, 64, sn && sn->valid ? sn->sni_certs_issued : 0);
        draw_kpi(hdc, 688, 78, 155, 64, L"SNI certs", val);
        long long up = sn && sn->valid ? sn->uptime_seconds : 0;
        swprintf(val, 64, L"%lldh %lldm", up / 3600, (up % 3600) / 60);
        draw_kpi(hdc, 855, 78, 125, 64, L"Uptime", val);

        RECT ph = {20, 158, 490, 410};
        draw_chart(hdc, ph, L"Hourly (last 24h)",
                   sn ? sn->hist : NULL, sn ? sn->hist_count : 0);
        RECT pd = {510, 158, 980, 410};
        draw_chart(hdc, pd, L"Daily (last 30d)",
                   sn ? sn->daily : NULL, sn ? sn->daily_count : 0);
        draw_legend(hdc, 40, 388);
        draw_legend(hdc, 530, 388);

        char cert_path[1024];
        snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
        long long days = cert_days_left(cert_path);
        HFONT lf = CreateFontW(15, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
            DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
            DEFAULT_PITCH | FF_SWISS, L"Segoe UI");
        SelectObject(hdc, lf);
        int ok = days > 0;
        int trusted = cert_trusted(cert_path);
        if (ok)
            swprintf(txt, 256, L"Certificate: valid for %lld days more", days);
        else
            swprintf(txt, 256, L"Certificate: unavailable (check resources folder)");
        SetTextColor(hdc, ok ? RGB(20, 120, 60) : RGB(190, 70, 70));
        TextOutW(hdc, 20, 418, txt, (int)wcslen(txt));
        swprintf(val, 64, L"trusted: %s", trusted ? L"YES" : L"NO");
        SetTextColor(hdc, trusted ? RGB(20, 120, 60) : RGB(190, 70, 70));
        TextOutW(hdc, 550, 418, val, (int)wcslen(val));
        SetTextColor(hdc, RGB(120, 130, 145));
        TextOutW(hdc, 20, 560, g_status, (int)wcslen(g_status));
        SelectObject(hdc, old);
        DeleteObject(lf);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        KillTimer(hwnd, 1);
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
    HWND hwnd = CreateWindowExW(0, L"ADBlockDashWin11", g_title,
        (WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX),
        CW_USEDEFAULT, CW_USEDEFAULT, 1000, 620,
        NULL, NULL, hinst, (LPVOID)args);
    if (!hwnd) {
        WSACleanup();
        return -1;
    }
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    WSACleanup();
    return 0;
}
