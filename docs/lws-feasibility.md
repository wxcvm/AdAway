# LWS feasibility (isolated spike, not a production migration)

## Contract and dependency pins

This spike does not link into the app, change the Windows production-core pin, change package identity, or publish installers. The current UI/export branches are separate and untouched. Both backends use the same application handler, P-256 certificate generator, 256-entry certificate cache, 128-connection admission cap, 4 KiB send chunks, 16 KiB header/input allowance, image and test logging.

Inputs are pinned in `tools/lws-spike/dependencies.lock.json`: LWS `a842936f881921b6462b3328cce8e700b7a7ecd2`, OpenSSL 3.5.9 `45e844fa2a14ec92d146bd8f5778ac130b6625fb`, fixture/Mongoose inputs from Android `bfc8c7a3f5b11de8e738f3cfec09bb3d3c44ded8`. Fetches verify commit identity, clean source state and fixture SHA-256. No moving branch is a build input.

The main LWS license is MIT with retained component notices. The source tree is not relicensed. The existing Mongoose GPL-2.0-only / OpenSSL3 Apache-2.0 distribution question is not resolved by publishing this spike; baseline executables and new APK/installers are not spike artifacts.

## Dynamic certificate gate

The documented `LWS_CALLBACK_OPENSSL_LOAD_EXTRA_SERVER_VERIFY_CERTS` callback receives the vhost's public OpenSSL `SSL_CTX`. The adapter installs `SSL_CTX_set_cert_cb`; after LWS's unchanged SNI selection, the public OpenSSL callback assigns the leaf and key with `SSL_use_certificate` and `SSL_use_PrivateKey`. It never replaces an LWS context, reads an LWS private structure or patches upstream. Each TLS vhost installs the hook. One service thread is explicit; there are no asynchronous TLS worker threads. This is not a proof of a future multithreaded cache.

The Mongoose adapter uses its public `struct mg_tls` to install the same per-SSL callback after `SSL_new`. Both backends therefore execute the same certificate generator/cache. The CA is generated into an isolated test directory and only loaded into the test client's in-memory trust configuration; no OS store is changed. Imported test CA files must be a matching self-signed CA pair. Intermediate-chain support is intentionally not claimed by this root-CA fixture.

## Build and test

Use `python3 tools/lws-spike/build.py --directory <OUT> --openssl <PINNED_PREFIX>`. Windows runs from MSYS2 with CMake, MinGW, Perl, Git and Make available. Linux host crypto can be built with `build-openssl-host.sh <OUT>`. Android reuses the existing fixed OpenSSL builder and NDK 28.1.13356709. Measured builds reject a crypto version other than 3.5.9. `--preflight` is an explicit local-diagnostics exception, recorded as such, never performance acceptance.

CMake disables HTTP2/HTTP3/QUIC, SChannel, Secure Streams, LHP, STUB/spawn, media decoding, extensions, unrelated event libraries and plugins. Generic crypto remains enabled because pinned LWS TLS certificate-digest code requires it. MinGW requires a standard pthread header force-include. Upstream signedness warnings are retained in build logs rather than hidden; spike adapters compile with `-Wall -Wextra -Werror`. These build findings are not private source patches.

`tests/correctness.py` drives actual loopback server processes and strict TLS clients, with byte hashes, HTTP status and MIME, conditional requests, persistent requests, WebSocket echoes/counters, SNI miss/hit, all 256 cache slots, eviction while a TLS connection remains alive, renewal, slow/early-closed clients, normal shutdown, per-family and wildcard binding, conflicts and missing-address rollback. Local smoke omits wildcard binding; CI uses disposable hosts. Native `spike_probe` runs in API30/API35 emulators and performs real IPv4/IPv6 and strict TLS calls inside Android, not host-side adb forwarding. It refuses to operate on real devices.

## Acceptance status

Status is **EVIDENCE INCOMPLETE** until the pinned builds, sanitizer runs, Android runtime checks, bounded forwarding/lifecycle checks and paired performance measurements have all completed. A successful minimal HTTPS test alone is not a migration recommendation. The local OpenSSL3.6 preflight is not an OpenSSL3.5.9 result.

The complete production server remains reference-only. This fixture does not implement production qlog v1, cumulative stats, UID attribution, Root startup, Windows UI or packaging. Migration still needs an inventory of `mg_http_*`, `mg_json_*`, `mg_iobuf`, timers, file/persistence helpers, WebSocket publication, proxy cancellation, TLS counters and management access restrictions. No full-production acceptance follows from the fixture.

Required final outcome: GO only after critical correctness is complete and stable paired P95, CPU/request and peak RSS regressions are each <=15%; NO-GO for a demonstrated unsupported critical capability or confirmed performance regression; INCONCLUSIVE for missing platforms/data or >10% paired variation. Performance parity can still justify a later migration for licensing/maintenance reasons. No production switch is automatic.
