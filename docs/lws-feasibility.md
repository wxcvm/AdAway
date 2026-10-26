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

## Forwarding and lifecycle follow-up

The fixture adds only two controlled routes, `/forward/text` and `/forward-tls/text`, targeting the configured loopback ports. HTTPS forwarding requires an explicit test CA. LWS uses its public HTTP mounts and `lws_callback_http_dummy`; the baseline uses `mg_http_connect`. Neither backend accepts arbitrary public upstream addresses. The prototype supports bodyless GET forwarding only, not a production proxy. LWS's native proxy streams bounded read chunks, while the baseline buffers a maximum 16 KiB upstream message; this difference is not included in the five performance workloads and still needs broader production cancellation/body-policy work.

Incoming connections are capped at 128, simultaneous forwards at 64. Forward start counters are observations, not completed upstream responses. Tests hold a controlled upstream response until the downstream closes, then require child closure before the ordinary timeout; this is not a sleep-based cancellation guess. HTTP close and protocol-drop both refund LWS admission exactly once. The baseline detaches its task before publishing an allocation failure. Tests also exercise partial-header timeout, oversized headers, capacity recovery, slow receivers/early close, restart on the same ports, strict untrusted-upstream rejection, and actual certificate expiry independently of cache age. Capacity tests use a common 10-second timeout to avoid silently testing idle expiry instead of admission; normal runs and benchmarks use 2 seconds.

Both ordinary IP access and the native no-SNI/IP-verification path retain the default certificate's IP SANs. Cache renewal assigns new per-SSL references, including default-certificate renewal, without invalidating in-flight SSL owners. This is not a test of arbitrary imported intermediate chains.

## Portability findings and fair measurement corrections

- Bionic includes pthread in libc: `Threads::Threads` replaces an invalid unconditional `-lpthread`.
- The pinned LWS CMake option `LWS_PLAT_ANDROID` removes stdin sources but its generated C config omits that macro. The parent supplies `-DLWS_PLAT_ANDROID=1` consistently with the selected public platform option, without changing upstream files.
- MSVCRT and UCRT differ on `fopen("wbx")`. The fixture uses `_sopen_s` with `_O_CREAT|_O_EXCL` for that exact internal mode. Existing files are not truncated, and partial/invalid CA pairs fail initialization.
- Windows baseline compilation now matches production's `MG_ENABLE_POLL=1`. The earlier default select backend silently hit Winsock's 64-entry fd_set limit. A standard 256-entry fallback definition fits the same 128+64 resource budget; this does not raise the admission cap. No baseline implementation is copied or patched.
- Both adapters use explicit fixture upstream trust. LWS disables implicit OS-CA loading for server vhosts, matching the baseline's lack of unused client trust stores. Loopback forwarding explicitly disables inherited proxy settings. Neither change modifies system proxy or trust configuration.
- Own code is compiled with strict warnings-as-errors. Vendor warnings remain visible but nonfatal for **both** libraries; this includes the pinned Windows LWS socket signedness diagnostic and Mongoose's WSAPoll zero-count diagnostic. Upstream code remains unmodified.
- `build-evidence.json` records resolved CMake options, executable sizes/digests (not binaries), source hashes and pinned inputs. `idle_rss` is captured before warm-up, not mislabeled warm working set. Pairs are joined by round number.

## Evidence ledger and current decision

**Current decision: NO-GO for the present adaptation / 不建议当前方案迁移. Do not switch production. Performance evidence is separately incomplete/noisy; this is a correctness decision, not a claim that LWS is inherently slower or can never work.**

The original default branches were Android `bfc8c7a3f5b11de8e738f3cfec09bb3d3c44ded8` and Windows `51e77155ad267bd0c1001c15286e089836bb2a80`, with default CI 40173416176 / 40173541018 successful. Independent `spike/lws-feasibility-v1` worktrees preserve the original dirty UI/export branches.

- Shared prototype review: [Android draft PR 97](https://github.com/wxcvm/AdAway/pull/97).
- Windows fixed-source consumer: [Windows draft PR 38](https://github.com/wxcvm/AdAway-Windows/pull/38).
- Fixed OpenSSL3.5.9 Linux process/sanitizer evidence at `c57552953dfa38aaa9277cb3aaa46a047d863ff6`: [run 40198619201](https://github.com/wxcvm/AdAway/actions/runs/40198619201). This predates the expanded forwarding tests; do not call the expanded code sanitizer-accepted from this run.
- Corrected MSVCRT file creation, fixed3.5.9 Windows process evidence: [run 40201091707](https://github.com/wxcvm/AdAway-Windows/actions/runs/40201091707), Windows `7e8b6a472bd2e9f4ad9dd170c9c82a8e3e0f6df7`, shared Android `a5c9fd582f18993054ddf3bd2fe8b258968fe921`. This also predates expanded tests.
- Earlier five-round runs each produced 300 phases (5 workloads x 2 log modes x 3 concurrency levels x 5 rounds x 2 backends): [Linux 40196456524](https://github.com/wxcvm/AdAway/actions/runs/40196456524), [Windows 40196591111](https://github.com/wxcvm/AdAway-Windows/actions/runs/40196591111). Linux had 0 client errors but 8/30 groups exceeded 10% paired variability; Windows had 100 client errors and 25/30 noisy groups. Neither run passed acceptance. Both precede the resource/trust/idle-metric corrections above and are retained as debugging evidence, not a final ranking. Raw JSON identities are in `docs/lws-baseline-evidence.json` and the named Actions artifacts.

The frozen-source matrix must still be joined with its own sanitizer, Android ABI/runtime, Windows runtime and full paired measurements. API30/API35 native probes verify real in-emulator IPv4/IPv6 and strict chain/name rejection; they are not the entire host Python suite or Android17 real-device/UI acceptance. arm64-v8a and riscv64 remain build-only without a matching runtime. The Linux job additionally builds the unchanged complete production-source tree (verified JNI tree e0c8408c6369c01e28069fdc073ec6fefd378681) with only a host Android-log shim, observes ordinary HTTP/strict HTTPS on both loopback families, and records a separate 2-second-warm/5-second single-client reference. It is not the device executable or a matched comparison; do not derive an LWS speedup from it. Its evidence remains pending until the actual job succeeds.

DeepSeek Flash was used for bounded read-only certificate and adapter reviews, not as an acceptance oracle. An admission-refund concern was reproduced and corrected. The claim that replacing DNS-name checks with the actual IP-SAN check removes CA validation was rejected against the pinned code and strict wrong-CA test. A possible allocation-failure task lifetime edge was hardened. Private upstream structures were not added. All findings are subject to executable tests and source review.

## Migration gap and indicative effort (not implementation approval)

`docs/lws-migration-inventory.json` lists lexical public `mg_*` call sites at the production baseline: 29 unique names in five own-source files, including a local wrapper and fixtures. It is not a reachability graph or complete type inventory. Windows has its own inventory because GUI, updater and qlog consumers use Mongoose JSON/HTTP helpers even if the server is replaced. Merely changing listen/TLS calls would therefore leave the dependency in the product.

| Work package | Remaining production responsibilities | Indicative engineering days |
|---|---|---:|
| transport adapter | server callbacks, raw connection semantics, proxy backpressure/cancel, half-close, timeouts | 4-7 |
| protocol utilities | HTTP parser/header/query helpers, JSON token iteration/escaping, buffers, string helpers, SHA/random/timing | 3-5 |
| server contracts | qlog v1 cursor/generation/filter, statistics, persistence, WS publication, diagnostics, access restrictions | 4-7 |
| cross-platform integration | Android Root start/stop, MinGW overlays and fixed pins, packaging and recovery | 3-5 |
| end-to-end acceptance | API30/35, actual Android17 device, Windows GUI/install/update/recovery, stable matched benchmarks | 3-6 |

Total initial range: **17-30 engineering days**, an estimate, not elapsed calendar time or a promise. Fixing issues uncovered by the spike, broader raw-connection scenarios, production reference measurement and third-party notice inventory may change it. Existing production Mongoose/OpenSSL distribution obligations are not changed or discharged by adding this prototype. This branch ships only validation source/documentation and JSON/log evidence; no new application build, update-channel change, preview release or production switch is authorized by its merge.

### Strict-upstream refusal follow-up

Pinned 3.5.9 Linux tests exposed an invalid leading chunk terminator when the delegated proxy reached completion without an HTTP status after a rejected upstream handshake. The adapter now tracks public established-response events and closes a failed forward cleanly before delegating any body/completion event. The test deliberately still rejects `BadStatusLine`: catching it as an acceptable refusal would conceal malformed output. Incoming and parent-linked outgoing adoption are also counted in separate budgets, proven by 64 held forwards plus a healthy peer and the 65th-forward rejection. No upstream source patch or TLS-verification relaxation was introduced.

### Android runner and native-body evidence

Android arm64-v8a, x86_64 and riscv64 compiled with pinned3.5.9 at `50746b7a677765c5a716c8ebf924bbd2ebfdeab2`. Its emulator runner was not accepted: the background wrapper retained an ADB output pipe until the 90-second service lifetime ended, and the native probe then found zero served requests. The wrapper now redirects all three streams at its boundary, uses nohup, binds every ADB operation to the checked emulator serial, uses a unique owned directory and bounds host calls; cleanup validates the owned child command line before signalling it. No real device is selected or modified.

The native client now validates Content-Length framing and the exact pinned image SHA-256, text and error bodies, not just a 200 status. Host CI also runs a deliberate corrupt-image negative case for both backends; the native client must reject it. Positive runs still require all 18 dual-family/strict-TLS checks. Linux full process, ASan/UBSan and separate production-source reference passed at `dc0026e60ac6039ec4f97cbfcec9a7a3447f55b1` in [40253250345](https://github.com/wxcvm/AdAway/actions/runs/40253250345); Android runner evidence remains pending the corrected runner version.

### Latest executable matrix

At shared source `554e4fc0fdde3b1a3e869c9d6a608a940c79e3f7`, [40253918889](https://github.com/wxcvm/AdAway/actions/runs/40253918889) passed the Linux full process suite, ASan/UBSan, separate production reference, arm64-v8a/riscv64 builds and actual x86_64 native clients inside **both API30 and API35** emulators. This is still not Android17 hardware acceptance. Host positive native-body checks and deliberately corrupted image rejection are included; Mongoose benchmark executables and CA private keys are not artifacts. Final quality-only changes still require their own rerun.

The host reference logging shim retains Android's required public variadic log ABI. Its generic no-ellipsis recommendation has a single explicitly documented ABI exception at the shim declaration; there is no format/va_arg traversal. No protocol/TLS/lifetime defect or production warning is suppressed by this exception, and the application translation unit remains unchanged.

## Windows full-process blocking finding (2026-10-26)

At Windows `088c56ec676fe87f8309d4894ab1d3d3c73029bb` / shared `554e4fc0fdde3b1a3e869c9d6a608a940c79e3f7`, [40253927417](https://github.com/wxcvm/AdAway-Windows/actions/runs/40253927417) failed the actual forwarding assertion: Content-Length4096, IncompleteRead4018, missing78 bytes. The normal and explicit diagnostic runs at Windows `473ebf23afce385ae30901f2c6446043ee9a2fc5` / shared `20042a59204a700bbb3e014351e4216d9b2ccb34` reproduced it in [40254812604](https://github.com/wxcvm/AdAway-Windows/actions/runs/40254812604).

The diagnostic identifies the FIRST route `/forward/text` over IPv4 plain HTTP, not an upstream TLS verification problem. The client-side log records 4013 bytes buffered after parsing headers, then `close_and_handled`, the public COMPLETED_CLIENT_HTTP callback from the h1 close path and downstream PROXY_TRANS_END/close, without the complete4096-byte body. The pinned public dummy's close path can append a five-byte terminator; this may explain4013 vs4018, but final tail bytes must be read from the new failure record before claiming that explanation as confirmed. We did not attribute the result to OpenSSL3.5 merely because the UCRT/OpenSSL3.6 preflight passed: the actual failing route is unencrypted.

This is a blocking result for the **current minimal public-mount adapter**, not proof that every public LWS client/event-loop design is impossible. No private HUP/state-field patch, timeout inflation, buffer-limit increase, response padding or swallowed IncompleteRead is used to make it pass. A separate client adapter or a different supported event backend would be a further experiment, not an approved production switch. This round's critical complete-response criterion is unmet; the two PRs stay draft and automatic merge is NOT triggered while required correctness remains failed.

The Windows workflow now continues independent baseline/native evidence collection after one backend fails and preserves partial records as `complete:false`. HTTP-only differential success explicitly reports incomplete source runs and is not full acceptance. Manual performance collection independently rebuilds non-diagnostic binaries, records the exact measured build and still leaves the workflow FAILED for the required correctness defect. Obtaining throughput data is not clearing that defect.
