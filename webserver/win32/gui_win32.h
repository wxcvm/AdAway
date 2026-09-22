#ifndef ADBLOCK_GUI_WIN32_H
#define ADBLOCK_GUI_WIN32_H
#include <stdbool.h>

/* Version marker - shown in the window title and the console startup log. */
#define ADBLOCK_APP_VERSION "1.48"

struct adblock_gui_args {
    const char *resource_dir;   /* folder holding localhost-2410.crt/.key */
    int http_port;
    int https_port;
    int stats_port;             /* loopback management port (statistics/control) */
    bool bind_all;              /* listen on all interfaces */
    bool start_minimized;       /* show only the tray icon (autostart) */
    bool owns_server;           /* true when this process runs the server thread */
    const char *startup_warning;/* non-null when the server could not bind */
};

/* Run the native dashboard window (blocks until the window is closed).
   Returns 0 on success, non-zero on failure. */
int adblock_gui_run(const struct adblock_gui_args *args);

/* Install/remove the HKCU Run ("Start with Windows") entry.
   cmdline is the full quoted command line of the exe + arguments. */
int win32_autostart_set(bool enable, const char *cmdline);

/* Query whether the autostart entry is currently installed. */
bool win32_autostart_installed(void);

/* Append one line to webserver.log (used for the dashboard heartbeat). */
void win32_log_line(const char *fmt, ...);

/* 0/1: whether the server worker thread of this process is still running
   (it is set to 0 when the mongoose poll loop returns). */
bool win32_server_alive(void);

/* The dashboard calls this for "apply & restart": the server is stopped
   first, then main() relaunches a fresh instance with the new settings. */
void win32_notify_restart(void);
bool win32_restart_requested(void);

#endif
