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
    * hot-reloadable block_config.json, allowlist.txt filtering;
    * /internal-stats (JSON statistics), /internal-ws (realtime push)
      and /internal-test (test page).

Usage:
  1. Extract the zip anywhere (e.g. C:\ADBlock).
  2. Run:
       webserver.exe --resources resources --http-port 8080 --https-port 8443

     Options:
       --resources <dir>  directory containing img_*.webp (block
                          placeholder images), test.html, optional
                          allowlist.txt / block_config.json (REQUIRED;
                          the CA certificate is generated there on first
                          run)
       --http-port N      HTTP  listen port  (default 80)
       --https-port N     HTTPS listen port (default 443)
       --bind all         listen on all interfaces (default: loopback
                          127.0.0.1 / [::1] only)
       --debug            verbose mongoose logs

  3. Open https://localhost:8443/internal-test (or your chosen port) to
     verify the server. The CA cert (localhost-2410.crt) is written to
     the resources dir; install/trust it if you want https:// browsing
     without certificate warnings.

Notes:
  * Ports 80/443 require administrator privileges; use higher ports
    (as above) otherwise.
  * Per-app statistics are reported as "unknown" on Windows: the
    Android /proc-based per-app uid tracing has no Windows equivalent.
  * The certificate-data (stats.dat, hist.dat, apps.dat, sni_cache.dat)
    is written next to the resources dir, so keep that folder writable.
