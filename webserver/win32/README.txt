ADBlock Web Server - Windows 11 (x64) standalone build
=========================================================

What it is:
  The C web server component from the ADBlock (AdAway fork) project
  (webserver/jni/webserver.c + mongoose + OpenSSL), packaged as a native
  Windows 11 x64 executable. It serves:
    * block placeholder images / empty JS / CSS / fonts / media / API
      replies with the same CORS, caching and block-reply policy as the
      Android build;
    * per-domain HTTPS certificates (SNI) signed by its own CA (with
      cache + expiry-aware re-issue);
    * hot-reloadable block_config.json / allowlist.txt filtering;
    * /internal-stats (JSON statistics), /internal-ws (realtime push)
      and /internal-test (test page).

Quick start (double-click friendly):
  1. Extract the zip anywhere (e.g. C:\ADBlock). Keep ALL files together
     (webserver.exe + the .dll files + the resources folder + start.bat).
  2. DOUBLE-CLICK start.bat  ----  that is all.
     It starts the server on http://localhost:8080 and
     https://localhost:8443 using the resources folder next to the exe.
  3. Open the test page:  https://localhost:8443/internal-test

Command line (optional):
  webserver.exe --resources resources --http-port 8080 --https-port 8443
     --resources <dir>  resources dir (block images img_*.webp, test.html,
                        allowlist.txt / block_config.json). If omitted or
                        missing it defaults to the "resources" folder
                        next to the executable and is created automatically.
     --http-port N      HTTP  listen port  (default 8080)
     --https-port N     HTTPS listen port (default 8443)
     --bind all         listen on all interfaces (default: loopback
                        127.0.0.1 / [::1] only)
     --debug            verbose mongoose logs

Using the official 80/443 ports:
  Ports 80/443 need Administrator privileges. To use them, run a
  terminal as Administrator and start the server with
  --http-port 80 --https-port 443.

Windows SmartScreen / antivirus:
  The executable is unsigned, so Windows may show "Windows protected
  your PC". Click "More info" -> "Run anyway". If an antivirus
  complains about the generated certificates, allow it - the CA is
  generated locally in the resources folder on first start.

Troubleshooting:
  * "Cannot create resources dir" - the folder is not writable. Extract
    the zip to your user folder / Desktop (not Program Files) or pass
    --resources with a writable path.
  * "CA generation failed" - same cause: resources dir must be writable.
  * Server exits right away - run it from a console (start.bat keeps it
    open) and read the [FATAL] line; the most common causes are a
    non-writable resources dir or ports already in use (use 8080/8443
    or change --http-port/--https-port).
  * Per-app statistics show "unknown" on Windows: the Android
    /proc-based uid tracing has no Windows equivalent.
  * Certificate-data (stats.dat, hist.dat, apps.dat, sni_cache.dat) is
    written next to the resources dir - keep that folder writable.
