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
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define UPDATE_API_HOST L"api.github.com"
#define UPDATE_API_PATH L"/repos/wxcvm/AdAway/releases?per_page=30"
#define UPDATE_MAX_BYTES (8u * 1024u * 1024u)

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
            if (status == 200) out = _wfopen(file, L"wb");
            if (out) {
                ok = 1;
                for (;;) {
                    DWORD avail = 0, read = 0;
                    char buffer[65536];
                    if (!WinHttpQueryDataAvailable(request, &avail) || avail == 0) break;
                    if (avail > sizeof(buffer)) avail = (DWORD) sizeof(buffer);
                    if (!WinHttpReadData(request, buffer, avail, &read) || read == 0) { ok = 0; break; }
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
static int find_latest_update(wchar_t *tag_out, size_t tag_cap, wchar_t *url_out, size_t url_cap) {
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
    struct update_info *info = NULL;
    if (find_latest_update(tag, 128, url, 1024)) {
        info = (struct update_info *) calloc(1, sizeof(*info));
        if (info) {
            wcsncpy(info->tag, tag, 127);
            wcsncpy(info->url, url, 1023);
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
    wchar_t temp[MAX_PATH], zip[MAX_PATH], dir[MAX_PATH], src[MAX_PATH];
    wchar_t appdir[MAX_PATH], exe[MAX_PATH], cmd[4096], script[8192], bat[MAX_PATH];
    DWORD pid = GetCurrentProcessId();

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
        swprintf(zip, MAX_PATH, L"%sadblock-update%s", temp, ext);
    }
    swprintf(dir, MAX_PATH, L"%sadblock-update", temp);
    swprintf(bat, MAX_PATH, L"%sadblock-update.bat", temp);

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
        free(info);
        return 0;
    }
    /*
     * The preferred package is the Inno Setup installer: it stops the running
     * server (taskkill in its [Code] section), replaces the files, recreates
     * the shortcuts and restarts the server - no batch script needed.
     */
    if (file_is_exe(zip)) {
        wchar_t run[2048];
        swprintf(run, 2048, L"\"%s\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART", zip);
        ShellExecuteW(NULL, L"open", zip, L"/VERYSILENT /SUPPRESSMSGBOXES /NORESTART", NULL, SW_SHOWNORMAL);
        (void) run;
        free(info);
        if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_CLOSE, 0, 0);
        return 0;
    }
    if (!file_is_zip(zip)) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"下载更新失败，请检查网络后重试（也可手动下载 zip 覆盖）。",
                    L"更新", MB_OK | MB_ICONWARNING);
        free(info);
        return 0;
    }
    swprintf(cmd, 4096, L"cmd.exe /c rmdir /S /Q \"%s\"", dir);
    run_and_wait(cmd);
    swprintf(cmd, 4096, L"cmd.exe /c mkdir \"%s\"", dir);
    run_and_wait(cmd);
    swprintf(cmd, 4096, L"tar.exe -xf \"%s\" -C \"%s\"", zip, dir);
    int rc = run_and_wait(cmd);
    if (rc != 0 && !find_extracted_dir(dir, src, MAX_PATH)) {
        MessageBoxW(NULL, L"解压更新包失败，请手动下载 zip 覆盖安装。", L"更新", MB_OK | MB_ICONWARNING);
        free(info);
        return 0;
    }
    if (!find_extracted_dir(dir, src, MAX_PATH)) {
        wcsncpy(src, dir, MAX_PATH - 1);
        src[MAX_PATH - 1] = 0;
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
             L"start \"\" \"%s\\webserver.exe\" --minimized\r\n"
             L"del \"%%~f0\"\r\n",
             (unsigned long) pid, src, appdir, appdir);
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
    }
    free(info);
    if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_CLOSE, 0, 0);
    return 0;
}

void update_apply_async(HWND hwnd, const struct update_info *info) {
    s_update_hwnd = hwnd;
    struct update_info *copy = (struct update_info *) calloc(1, sizeof(*copy));
    if (!copy) return;
    *copy = *info;
    CloseHandle(CreateThread(NULL, 0, apply_thread, (LPVOID) copy, 0, NULL));
}
