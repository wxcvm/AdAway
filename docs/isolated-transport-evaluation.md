# Historical fixed-commit userspace transport evaluation

This describes the old b1ce1e6 Linux/NDK harness, not current upstream or the latest app. For current TCP backend integration and remaining boundaries see [tcp-backend.md](tcp-backend.md). The old PR dependency and missing-platform statements below are historical.

Depends on draft Android M1 PR #46. This is an evaluation harness, not an
Android capture implementation or a selected production dependency. It does
not change the application, package libraries in its APK, create a kernel
interface, modify routes/DNS, use root, merge or publish a release.

## One candidate and license audit

`heiher/hev-socks5-tunnel` is evaluated at exact commit
`b1ce1e6c2f572c1b6fecf262c9ea2d7b0916c947`. Its four recursive dependency
commits are asserted by `tools/transport-poc/fetch_candidate.sh`; no moving
branch is accepted. The main project, SOCKS core, task system and YAML library
have MIT notices; lwIP has the three-clause BSD notice. Any later distribution
must retain those notices. This Linux/Android evaluation does not link or
distribute Wintun. Its separate license, signed binary provenance and Windows
deployment remain a separate evaluation. No other proposed stacks or rule/DNS
engines are installed by this harness.

## Reproduce

Use a new scratch directory; the fetch helper refuses to replace an existing
directory. Build with two workers and upstream's stack-overflow detector.
The coroutine task system deliberately disables stack-clash instrumentation;
this build exception requires review before production adoption.

```sh
bash tools/transport-poc/fetch_candidate.sh /tmp/adaway-hev-evaluation
cd /tmp/adaway-hev-evaluation
make -j2 shared CFLAGS='-O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2'
LD_LIBRARY_PATH=third-part/yaml/bin:third-part/lwip/bin:third-part/hev-task-system/bin \
  python3 /workspace/AdAway/tools/transport-poc/loopback_probe.py bin/libhev-socks5-tunnel.so
NDK=/workspace/setup-tools/android-sdk/ndk/28.1.13356709
"$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK=Application.mk APP_ABI='arm64-v8a x86_64' -j2
python3 /workspace/AdAway/tools/transport-poc/check_android_abi.py . \
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
```

The library receives an AF_UNIX datagram socket descriptor as a mock TUN. Its
external-descriptor path skips kernel interface creation/configuration. All
real TCP/UDP sockets bind/connect only to loopback. The bounded SOCKS fixture
allows only its exact two test destinations: synthetic addresses are mapped
to this process's echo endpoints, never forwarded externally. Fixture sockets
and threads, library quit/join and caller-owned descriptors are cleaned up.
The probe checks exact payloads, response endpoints/ports, IP lengths and IP,
TCP and UDP checksums (IPv4's permitted absent UDP checksum is accepted).
IPv6 control packets are independently checksum-checked before being skipped.

Executed on this Linux cloud host:

| Check | Result |
|---|---|
| IPv4 UDP exact payload roundtrips | 50 passed |
| IPv6 UDP exact payload roundtrips | 10 passed |
| IPv4 TCP SYN/ACK, payload echo, FIN/ACK | 16,512 bytes passed |
| Library bounded quit and all fixture threads stopped | Passed |
| Android API 29 ARM64/x86_64 NDK r28b builds | Passed; not device execution |
| ELF load alignment, non-executable stack, RELRO/BIND_NOW | Both ABIs passed; alignment >=16 KiB |
| Library sizes | ARM64 348,024 bytes; x86_64 346,712 bytes |
| Android runtime dependencies | libc, libm, libdl only |

A single synthetic sample measured IPv4 UDP median 0.075 ms / p95 0.177 ms,
TCP echo 0.076 MiB/s, and whole Python process peak RSS 13,356 → 14,404 KiB.
The small stop-and-wait fixture and TCP ACK behavior dominate these numbers:
they are neither a saturation benchmark nor attributable library memory
measurements. They prove reproducible payload handling, not mobile/Windows
throughput, latency, memory leak freedom or battery consumption. CI uploads
its own JSON measurements rather than asserting these host-dependent values.

## Integration blockers and next entry point

- Upstream's lwIP configuration disables incoming IP/TCP/UDP/ICMP checksum
  checks, and the read loop inspects fixed header offsets. The platform must
  supply strict length/version/extension/fragment/checksum validation before
  forwarding untrusted packets, or a verified hardened upstream configuration
  must be developed. The current PoC sends controlled valid packets only; it
  provides no malformed-input or production parsing guarantee.
- The C API has process-global lifecycle/configuration. The supplied TUN FD is
  caller-owned; a future adapter needs readiness, ownership and stop/restart
  race handling. The upstream JNI registers `hev/htproxy/TProxyService`, which
  is absent from AdAway. An AdAway-specific bridge is required before loading.
- A local SOCKS backend needs Android `VpnService.protect()` and underlying
  `Network` binding for every actual upstream socket, bounded authentication,
  resource limits and network-generation invalidation. The fixture is not
  such a backend. There is no verified public protect callback in this candidate.
- Limits/timeouts in this probe are explicit (32 sessions, 2 s connect,
  3 s transport); saturation, fairness, restart and long-running leaks need
  meaningful tests before integration. IPv6 TCP, DNS TCP, NAT64, QUIC/game
  traffic and non-echo use cases are unverified.
- Android consent/revoke/foreground lifecycle, app exclusion, Always-on,
  Lockdown, network changes and real device browsing/file/video E2E are
  **BLOCKED** by the missing usable device. Windows adapter/recovery/physical
  E2E and battery measurements are also **BLOCKED**. Compilation and Linux
  mock-TUN payload tests do not satisfy those acceptance gates.

M1's Java snapshot and shared decision proposal still need production
Hosts/ABP-loader integration and proven native reload atomicity. M3 still
needs a typed durable OS intent/recovery executor before any Wintun routing.
Do not turn on a default route or advertise production forwarding based on
these evaluation results.
