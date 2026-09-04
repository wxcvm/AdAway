ADBlock Web Server - Windows 11 (x64) standalone build
=========================================================

What it is:
  The C web server component from the ADBlock (AdAway fork) project
  (webserver/jni/webserver.c + mongoose + OpenSSL), packaged as a native
  Windows 11 x64 executable. What it does:
    * Block placeholder images / empty JS / CSS / fonts / media / API
      replies (same CORS + caching policy as Android);
    * Per-domain HTTPS certificates (SNI) signed by its own CA;
    * Native dashboard window: live KPIs, hourly/daily bar charts,
      certificate status, "Trust CA" button and "Start with Windows";
    * Hot-reloadable block_config.json / allowlist.txt filtering;
    * /internal-stats (JSON), /internal-ws (realtime), /internal-test.

Quick start (double-click friendly):
  1. Extract the zip anywhere (e.g. C:\ADBlock). Keep ALL files together.
  2. DOUBLE-CLICK start.bat.
     - A dashboard window opens showing live statistics + charts.
     - Server listens on http://localhost:8080 and https://localhost:8443
       (resources folder next to the exe, created automatically).
  3. In the dashboard click:
     - "Trust CA"  - installs the local CA into Windows Trusted Root store
                     so https://localhost:8443 shows a green lock.
     - "Open Test Page" - opens https://localhost:8443/internal-test.
     - "Start with Windows" - registers autostart (HKCU Run key, no admin).
     - Close the window to stop the server.

Command line:
  webserver.exe [options]
    --resources <dir>    resources dir (img_*.webp, test.html, allowlist,
                         block_config.json). Default: "resources" next to
                         the exe (created if missing).
    --http-port N        HTTP  port (default 8080)
    --https-port N       HTTPS port (default 8443)
    --bind all           listen on all interfaces (default loopback)
    --debug              verbose mongoose logs
    --no-gui             run headless (no dashboard window)
    --install-autostart  register "Start with Windows" and exit
    --uninstall-autostart  remove the autostart entry and exit

Using ports 80/443:
  They need Administrator privileges: run a terminal as Administrator and
  pass --http-port 80 --https-port 443.

Windows SmartScreen / antivirus:
  If the exe is not code-signed, Windows may show "Windows protected your
  PC" -> click "More info" -> "Run anyway". A code-signed build is
  produced automatically when the repository has the
  WINDOWS_CERT_PFX_B64 / WINDOWS_CERT_PASSWORD secrets configured.

Troubleshooting:
  * "Cannot create resources dir" / "CA generation failed" - the folder
    is not writable: extract to your user folder or pass --resources.
  * Dashboard shows "no data yet" - wait a few seconds (or generate some
    blocked traffic); the server itself is fine.
  * Per-app statistics show "unknown" on Windows (no /proc equivalent).
