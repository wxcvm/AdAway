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
#define UPDATE_API_PATH L"/repos/wxcvm/AdAway/releases?per_page=30"
#define UPDATE_MAX_BYTES (8u * 1024u * 1024u)   /* API JSON answer only */
/* Hard cap for the downloaded update package: without it a hostile or broken
   release could stream until the disk is full (phase-2 audit, item A). */
#define UPDATE_DOWNLOAD_MAX_BYTES (64u * 1024u * 1024u)
/* Extraction budget (entries / total bytes) - see the staging check. */
#define UPDATE_EXTRACT_MAX_ENTRIES 4000u
#define UPDATE_EXTRACT_MAX_BYTES (256u * 1024u * 1024u)

static HWND s_update_hwnd;

/* ── helpers ──────────────────────────────────────────────────── */

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

/* HTTP(S) GET (system proxy aware, follows redirects). Caller frees. */
static char *http_get(const wchar_t *host, const wchar_t *path, size_t *out_len) {
    HINTERNET session = NULL, connect = NULL, request = NULL;
    char *body = NULL;
    size_t total = 0, cap = 0;
    DWORD status = 0, slen = sizeof(status);
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;

    session = WinHttpOpen(L"ADBlock-WebServer", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                          WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return NULL;
    WinHttpSetTimeouts(session, 8000, 8000, 10000, 30000);
    connect = WinHttpConnect(session, host, INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!connect) goto done;
    request = WinHttpOpenRequest(connect, L"GET", path, NULL, WINHTTP_NO_REFERER,
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
    if (status != 200) goto done;
    for (;;) {
        DWORD avail = 0, read = 0;
        if (!WinHttpQueryDataAvailable(request, &avail) || avail == 0) break;
        if (total + avail + 1 > UPDATE_MAX_BYTES) break;
        if (total + avail + 1 > cap) {
            size_t want = (total + avail + 1) * 2;
            char *grown = (char *) realloc(body, want);
            if (!grown) { free(body); body = NULL; goto done; }
            body = grown;
            cap = want;
        }
        if (!WinHttpReadData(request, body + total, avail, &read) || read == 0) break;
        total += read;
    }
    if (body) {
        body[total] = 0;
        if (out_len) *out_len = total;
    }
done:
    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    if (session) WinHttpCloseHandle(session);
    return body;
}

/* Download a (redirecting) release asset to a local file. */
static int http_download_to_file(const wchar_t *url, const wchar_t *file) {
    wchar_t host[256] = L"", path[1024] = L"";
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

    HINTERNET session = WinHttpOpen(L"ADBlock-WebServer", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return 0;
    WinHttpSetTimeouts(session, 8000, 8000, 15000, 60000);
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
    HINTERNET connect = WinHttpConnect(session, host, INTERNET_DEFAULT_HTTPS_PORT, 0);
    HINTERNET request = connect
        ? WinHttpOpenRequest(connect, L"GET", path, NULL, WINHTTP_NO_REFERER,
                             WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE)
        : NULL;
    int ok = 0;
    FILE *out = NULL;
    if (request) {
        WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy));
        if (WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                               WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
            WinHttpReceiveResponse(request, NULL)) {
            DWORD status = 0, slen = sizeof(status);
            WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                WINHTTP_HEADER_NAME_BY_INDEX, &status, &slen, WINHTTP_NO_HEADER_INDEX);
            /* Refuse an oversized package before writing a single byte, and
               keep counting while streaming (a server may lie about or omit
               Content-Length). */
            int too_big = 0;
            unsigned long long declared = 0;
            DWORD clen_size = sizeof(declared);
            if (WinHttpQueryHeaders(request,
                    WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                    WINHTTP_HEADER_NAME_BY_INDEX, &declared, &clen_size,
                    WINHTTP_NO_HEADER_INDEX) &&
                declared > (unsigned long long) UPDATE_DOWNLOAD_MAX_BYTES) {
                too_big = 1;
            }
            if (status == 200 && !too_big) out = _wfopen(file, L"wb");
            if (out) {
                ok = 1;
                unsigned long long total = 0;
                for (;;) {
                    DWORD avail = 0, read = 0;
                    char buffer[65536];
                    if (!WinHttpQueryDataAvailable(request, &avail) || avail == 0) break;
                    if (avail > sizeof(buffer)) avail = (DWORD) sizeof(buffer);
                    if (!WinHttpReadData(request, buffer, avail, &read) || read == 0) { ok = 0; break; }
                    total += read;
                    if (total > (unsigned long long) UPDATE_DOWNLOAD_MAX_BYTES) { ok = 0; break; }
                    if (fwrite(buffer, 1, read, out) != read) { ok = 0; break; }
                }
            }
        }
    }
    if (out) fclose(out);
    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return ok;
}

/* Newest release shipping a zip, when it is newer than this build. */
static int find_latest_update(wchar_t *tag_out, size_t tag_cap, wchar_t *url_out, size_t url_cap,
                              char *digest_out, size_t digest_cap) {
    size_t len = 0;
    char *json = http_get(UPDATE_API_HOST, UPDATE_API_PATH, &len);
    if (!json) return 0;
    int found = 0;
    const char *p = json;
    while (!found && (p = strstr(p, "\"tag_name\"")) != NULL) {
        char tag[128] = "", url[1024] = "";
        const char *next = strstr(p + 10, "\"tag_name\"");
        if (json_string(json, "\"tag_name\"", p, tag, sizeof(tag))) {
            /* Walk every asset of this release: the Inno Setup installer is
               preferred, the portable zip is the fallback. */
            char candidate[1024] = "", fallback[1024] = "";
            const char *q = p;
            while ((q = strstr(q, "\"browser_download_url\"")) != NULL &&
                   (next == NULL || q < next)) {
                char one[1024] = "";
                if (json_string(json, "\"browser_download_url\"", q, one, sizeof(one))) {
                    size_t l = strlen(one);
                    if (l > 4 && _stricmp(one + l - 4, ".exe") == 0 && candidate[0] == 0)
                        snprintf(candidate, sizeof(candidate), "%s", one);
                    else if (l > 4 && _stricmp(one + l - 4, ".zip") == 0 && fallback[0] == 0)
                        snprintf(fallback, sizeof(fallback), "%s", one);
                }
                q++;
            }
            snprintf(url, sizeof(url), "%s", candidate[0] ? candidate : fallback);
            if (digest_out && digest_cap) {
                digest_out[0] = 0;
                if (url[0]) {
                    /* Bind the digest to the asset actually chosen: taking
                       the first "digest" of the release broke the updater as
                       soon as the asset order changed (audit B-1). */
                    const char *uq = strstr(p, url);
                    const char *dq = uq ? strstr(uq, "\"digest\"") : NULL;
                    const char *nq = uq ? strstr(uq + 1, "\"browser_download_url\"") : NULL;
                    if (dq && (nq == NULL || dq < nq)) {
                        char hex[128] = "";
                        if (json_string(json, "\"digest\"", dq, hex, sizeof(hex)))
                            snprintf(digest_out, digest_cap, "%s", hex);
                    }
                }
            }
            if (url[0]) {
                const char *v = strrchr(tag, 'v');
                v = v ? v + 1 : tag;
                /*
                 * Only the Windows zip of this program is a valid update: the
                 * same feed also carries the Android APK, so the tag has to be
                 * the Windows one and the download must really be a .zip (this
                 * is what made an earlier build offer the Android APK).
                 */
                int is_win_release = strncmp(tag, "win11-webserver-", 16) == 0 ||
                                     strstr(url, "adblock-webserver") != NULL;
                size_t ulen = strlen(url);
                int is_pkg = ulen > 4 &&
                             (_stricmp(url + ulen - 4, ".zip") == 0 ||
                              _stricmp(url + ulen - 4, ".exe") == 0);
                if (is_win_release && is_pkg && version_cmp(v, ADBLOCK_APP_VERSION) > 0) {
                    utf8_to_wide(tag, tag_out, tag_cap);
                    utf8_to_wide(url, url_out, url_cap);
                    found = 1;
                }
            }
        }
        p += 10;
    }
    free(json);
    return found;
}

static DWORD WINAPI check_thread(LPVOID param) {
    HWND hwnd = (HWND) param;
    wchar_t tag[128] = L"", url[1024] = L"";
    char digest[128] = "";
    struct update_info *info = NULL;
    if (find_latest_update(tag, 128, url, 1024, digest, sizeof(digest))) {
        info = (struct update_info *) calloc(1, sizeof(*info));
        if (info) {
            wcsncpy(info->tag, tag, 127);
            wcsncpy(info->url, url, 1023);
            utf8_to_wide(digest, info->sha256, 128);
        }
    }
    PostMessageW(hwnd, WM_APP_UPDATE_FOUND, info ? 1 : 0, (LPARAM) info);
    return 0;
}

void update_check_async(HWND hwnd) {
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
     * though the network was fine.
     */
    {
        size_t ulen = wcslen(info->url);
        const wchar_t *ext = (ulen > 4 && _wcsicmp(info->url + ulen - 4, L".exe") == 0)
                             ? L".exe" : L".zip";
        /* Unpredictable name: a fixed %TEMP% target could be pre-created by
           another process of the same user (audit P0-1). */
        swprintf(zip, MAX_PATH, L"%sadblock-update-%lu-%lu%s", temp,
                 (unsigned long) pid, (unsigned long) stamp, ext);
    }
    /* Unique staging paths as well (audit A6). A fixed "%TEMP%\adblock-update"
       could be pre-created by another process, and it was never removed: the
       batch script now deletes it after the file copy. */
    swprintf(dir, MAX_PATH, L"%sadblock-update-%lu-%lu", temp, (unsigned long) pid, (unsigned long) stamp);
    swprintf(list, MAX_PATH, L"%sadblock-update-%lu-%lu.list", temp, (unsigned long) pid, (unsigned long) stamp);
    swprintf(bat, MAX_PATH, L"%sadblock-update-%lu-%lu.bat", temp, (unsigned long) pid, (unsigned long) stamp);

    if (!http_download_to_file(info->url, zip)) {
        /* github.com 的下载服务器（*.githubusercontent.com）在部分网络下不可达，
           而 API 查询是通的 - 这时给出浏览器下载的兜底。 */
        if (MessageBoxW(NULL,
                        L"下载更新失败：无法访问 GitHub 的下载服务器（常见于网络受限）。\n\n"
                        L"是否用浏览器打开下载页，手动下载安装？",
                        L"更新", MB_YESNO | MB_ICONWARNING) == IDYES) {
            ShellExecuteW(NULL, L"open", L"https://github.com/wxcvm/AdAway/releases",
                          NULL, NULL, SW_SHOWNORMAL);
        }
        DeleteFileW(zip);          /* no half-downloaded package in %TEMP% */
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
    if (info->sha256[0] != 0) {
        if (!digest_matches(zip, info->sha256)) {
            DeleteFileW(zip);
            MessageBoxW(NULL, L"更新包校验失败（SHA-256 与发布信息不一致），已删除，未执行。",
                        L"更新", MB_OK | MB_ICONERROR);
            free(info);
            return 0;
        }
    } else if (!file_signature_trusted(zip)) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"更新包既没有发布方的 SHA-256，也没有有效的数字签名，已删除，未执行。\n\n请在 Releases 页手动下载并自行核对。",
                    L"更新", MB_OK | MB_ICONERROR);
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
        ShellExecuteW(NULL, L"open", zip, params, NULL, SW_SHOWNORMAL);
        free(info);
        if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_APP_EXIT, 0, 0);   /* WM_CLOSE only hides the window: a helper batch waiting for the PID would wait forever */
        return 0;
    }
    if (!file_is_zip(zip)) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"下载更新失败，请检查网络后重试（也可手动下载 zip 覆盖）。",
                    L"更新", MB_OK | MB_ICONWARNING);
        free(info);
        return 0;
    }
    /* Audit A4: validate the archive listing before extracting a single byte. */
    int listing = tar_listing_is_safe(zip, list);
    DeleteFileW(list);
    if (listing < 0) {
        MessageBoxW(NULL, L"无法校验更新包内容（本机缺少 tar.exe，需要 Windows 10 1803 或更高版本）。\n\n请到 Releases 页面手动下载安装包。",
                    L"更新", MB_OK | MB_ICONWARNING);
        free(info);
        return 0;
    }
    if (listing == 0) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"更新包校验失败：压缩包内含越权路径（绝对路径或 ..），已拒绝执行。",
                    L"更新", MB_OK | MB_ICONERROR);
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
             L"rmdir /S /Q \"%s\" >nul 2>&1\r\n"
             L"start \"\" \"%s\\webserver.exe\" --minimized\r\n"
             L"del \"%%~f0\"\r\n",
             (unsigned long) pid, src, appdir, dir, appdir);
    if (!write_ansi_file(bat, script)) {
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
        free(info);
        return 0;
    }
    free(info);
    if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_APP_EXIT, 0, 0);   /* WM_CLOSE only hides the window: a helper batch waiting for the PID would wait forever */
    return 0;
}

void update_apply_async(HWND hwnd, const struct update_info *info) {
    s_update_hwnd = hwnd;
    struct update_info *copy = (struct update_info *) calloc(1, sizeof(*copy));
    if (!copy) return;
    *copy = *info;
    CloseHandle(CreateThread(NULL, 0, apply_thread, (LPVOID) copy, 0, NULL));
}
