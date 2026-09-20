#ifndef UPDATE_WIN32_H
#define UPDATE_WIN32_H

#include <windows.h>

/* Posted to the dashboard when the background update check finished.
   wParam = 1 when a newer release exists, lParam = struct update_info*
   (owned by the window, free with update_info_free), or NULL. */
#define WM_APP_UPDATE_FOUND (WM_APP + 21)
/* Posted while the update package is downloading; wParam = percent 0..100,
   or -1 when the download gave up (the dashboard clears the percentage). */
#define WM_APP_UPDATE_PROGRESS (WM_APP + 22)

struct update_info {
    wchar_t tag[128];
    wchar_t url[1024];
    wchar_t api_url[1024]; /* /releases/assets/<id> fallback download URL */
    wchar_t sha256[128];   /* "sha256:<hex>" published by the API, may be empty */
    /* The release's other package (installer <-> portable zip): used when the
       primary one cannot be downloaded (slow or blocked asset host). */
    wchar_t alt_url[1024];
    wchar_t alt_api_url[1024];
    wchar_t alt_sha256[128];
};

/* Check the release feed in the background (never blocks the UI). */
void update_check_async(HWND hwnd);
/* Download + extract the release and restart the executable afterwards. */
void update_apply_async(HWND hwnd, const struct update_info *info);
void update_info_free(struct update_info *info);
/* Reason of the last failed check (empty when the last check succeeded). */
const wchar_t *update_last_error(void);

#endif
